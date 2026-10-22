terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.69"
    }
  }

  # Remote state: uncomment and fill in for a real deployment.
  # backend "s3" {
  #   bucket         = "my-tf-state"
  #   key            = "url-shortener/terraform.tfstate"
  #   region         = "us-west-2"
  #   dynamodb_table = "terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "url-shortener"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
